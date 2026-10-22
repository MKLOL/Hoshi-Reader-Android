package moe.antimony.hoshi.features.mangareader

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class MangaImageDecodeCandidate(val url: String, val width: Int, val height: Int)

/** Renderer-side part of the image backend, with no dependencies on OCR, layout or gestures. */
internal object MangaImageDecodePreloader {
    fun script(pageIndex: Int, candidates: List<MangaImageDecodeCandidate>, budget: Long): String {
        // URLs can contain filenames supplied by an imported book. Encode as data, not JS/HTML.
        val urls = Json.encodeToString(candidates.map { it.url })
        val sizes = Json.encodeToString(candidates.map { estimatedBytes(it.width, it.height) })
        return """
            (function() {
              var frame = document.querySelector('.frame');
              if (!frame || Number(frame.dataset.pageIndex) !== $pageIndex) return;
              if (window.hoshiMangaImageCache) window.hoshiMangaImageCache.cancel();
              var urls = $urls, sizes = $sizes, budget = $budget;
              var state = { images: [], decoded: [], failed: [], skipped: [], bytes: 0, done: false };
              var stopped = false, pending = null, timer = null;
              state.cancel = function() {
                stopped = true;
                window.removeEventListener('pagehide', state.cancel);
                document.removeEventListener('visibilitychange', visibilityChanged);
                clearTimeout(timer);
                if (pending) pending();
                state.images.forEach(function(image) { image.removeAttribute('src'); });
                state.images = [];
                state.bytes = 0;
              };
              window.hoshiMangaImageCache = state;
              window.addEventListener('pagehide', state.cancel, { once: true });
              function visibilityChanged() { if (document.hidden) state.cancel(); }
              document.addEventListener('visibilitychange', visibilityChanged);
              function prepare(url, estimate) {
                return new Promise(function(resolve) {
                  var image = new Image(), settled = false;
                  var timeout = setTimeout(function() { finish(false); }, 10000);
                  function finish(ok) {
                    if (settled) return;
                    settled = true;
                    clearTimeout(timeout);
                    image.onload = image.onerror = null;
                    pending = null;
                    if (ok && !stopped) {
                      state.images.push(image);
                      state.decoded.push(url);
                      state.bytes += estimate;
                    } else {
                      image.removeAttribute('src');
                      if (!stopped) state.failed.push(url);
                    }
                    resolve();
                  }
                  pending = function() { finish(false); };
                  image.onload = function() {
                    // Check real dimensions too: malformed mokuro metadata must not defeat
                    // the budget. Loading bytes alone does not request an eager decode.
                    estimate = image.naturalWidth * image.naturalHeight * 4;
                    if (estimate <= 0 || estimate > budget - state.bytes) { finish(false); return; }
                    if (image.decode) image.decode().then(function() { finish(true); }, function() { finish(false); });
                    else finish(false);
                  };
                  image.onerror = function() { finish(false); };
                  image.decoding = 'async';
                  image.src = url;
                });
              }
              async function run() {
                for (var i = 0; i < urls.length && !stopped; i++) {
                  if (document.hidden) break;
                  if (sizes[i] <= 0 || sizes[i] > budget - state.bytes) { state.skipped.push(urls[i]); continue; }
                  await prepare(urls[i], sizes[i]);
                }
                state.done = true;
              }
              // Start only after the host's first visual-ready callback, yielding to the
              // visible page. One decode at a time avoids competing batches on slow tablets.
              timer = setTimeout(run, 0);
            })();
        """.trimIndent()
    }

    private fun estimatedBytes(width: Int, height: Int): Long =
        if (width <= 0 || height <= 0) 0 else {
            val pixels = width.toLong() * height
            if (pixels > Long.MAX_VALUE / 4) Long.MAX_VALUE else pixels * 4
        }
}
