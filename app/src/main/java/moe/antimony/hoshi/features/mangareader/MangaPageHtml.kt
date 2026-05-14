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
 * The OCR text is real, selectable DOM text (`<p>` per box). It is rendered transparent so
 * the artwork shows through, but it is present and hit-testable — tapping a word triggers
 * the shared selection bridge exactly like the EPUB reader. `<p>` is used because the
 * shared selection script ([moe.antimony.hoshi.features.reader.ReaderSelectionScripts])
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
     */
    fun build(
        page: MokuroPage,
        backgroundCssColor: String,
        selectionScript: String,
        scanNonJapaneseText: Boolean,
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
            ${pageCss(backgroundCssColor)}
            </style>
            </head>
            <body>
            <div class="page">
              <div class="frame">
                <img class="page-image" src="${escapeAttribute(page.imagePath)}" alt="">
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
            $selectionScript
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun pageCss(backgroundCssColor: String): String = """
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
          /* The OCR text is shown over the artwork: visible so the reader can see what is
             tappable, on a translucent light plate so it stays legible without fully
             hiding the art, and selectable so a tap looks the word up in the dictionary. */
          color: #000;
          background: rgba(255, 255, 255, 0.82);
          border-radius: 3px;
          -webkit-user-select: text;
          user-select: text;
        }
        .ocr-box.vertical {
          writing-mode: vertical-rl;
          text-orientation: upright;
        }
        .ocr-box p {
          margin: 0;
        }
        ::selection { background: rgba(70, 130, 220, 0.45); }
    """.trimIndent()

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
            """<p>$text</p></div>"""
    }

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
}
