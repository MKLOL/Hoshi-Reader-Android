package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox

/**
 * Generates the self-contained HTML document for a single mokuro manga page.
 *
 * Layout strategy — chosen so the absolutely-positioned OCR boxes line up with the image
 * at *any* rendered size, including the letterboxing from `object-fit: contain`:
 *
 *  - A `.page` flexbox centres a `.frame` element in the viewport.
 *  - `.frame` is sized with `aspect-ratio` matching the source image and grows to the
 *    largest size that fits the viewport (`max-width/height: 100%`, `width/height: auto`
 *    via the aspect-ratio + `object-fit`). The background `<img>` fills `.frame` exactly.
 *  - Every OCR box is a child of `.frame` positioned with **percentages** of the image's
 *    intrinsic pixel size. Percent positioning tracks `.frame`'s real rendered size, so no
 *    JS measuring / rescaling is needed and pinch-zoom keeps the text aligned.
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
        // `.frame` is given a *definite* size: the largest box with the image's aspect ratio
        // that fits the viewport. A definite size is what lets the OCR boxes position by
        // percentage and lets `cqw` font units resolve.
        val frameStyle = "width: min(100vw, calc(100vh * $imageWidth / $imageHeight)); " +
            "height: min(100vh, calc(100vw * $imageHeight / $imageWidth));"
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
              <div class="frame" style="$frameStyle">
                <img class="page-image" src="${escapeAttribute(page.imagePath)}" alt="">
                <div class="ocr-layer">
            $boxes
                </div>
              </div>
            </div>
            <script>
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
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .frame {
          position: relative;
          /* Concrete width/height (largest aspect-correct box fitting the viewport) are
             set inline per page. A definite size makes percentage-positioned OCR boxes and
             cqw font units resolve. inline-size containment is used so cqw = 1% of width. */
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
          /* Transparent but present: artwork shows through, text stays selectable on tap. */
          color: transparent;
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
