package moe.antimony.hoshi.features.reader

/** Word-aware wrapping and sentence-aware page boundaries; visible source text stays unchanged. */
internal fun readerTextLayoutScript(): String = """
(function () {
  'use strict';
  // Reuse one live Range: thousands of detached ranges make every later DOM mutation quadratic.
  var workingRange = document.createRange();
  var segmenters = {};
  var SKIP = 'rt,rp,script,style,noscript,svg,math,textarea,pre,code';
  var BLOCK = 'p,div,li,dd,dt,blockquote,figcaption,h1,h2,h3,h4,h5,h6,td,th,section,article';
  function runs() {
    var out = [], current = null;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentElement;
        return !parent || parent.closest(SKIP) || !node.nodeValue || getComputedStyle(parent).display === 'none'
          ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var node;
    while ((node = walker.nextNode())) {
      var block = node.parentElement.closest(BLOCK) || document.body;
      if (!current || current.block !== block) {
        current = { block: block, text: '', nodes: [] }; out.push(current);
      }
      current.nodes.push({ node: node, start: current.text.length });
      current.text += node.nodeValue;
    }
    return out;
  }
  function point(run, offset) {
    var low = 0, high = run.nodes.length;
    while (low < high) {
      var middle = (low + high) >>> 1;
      if (run.nodes[middle].start <= offset) low = middle + 1;
      else high = middle;
    }
    var item = run.nodes[Math.max(0, low - 1)];
    return { node: item.node, offset: Math.max(0, Math.min(offset - item.start, item.node.length)) };
  }
  function boundaryRange(run, start, end) {
    var a = point(run, start), b = point(run, end), range = workingRange;
    var firstRuby = a.node.parentElement.closest('ruby');
    var lastRuby = b.node.parentElement.closest('ruby');
    if (firstRuby && a.offset === 0 && firstRuby.textContent) range.setStartBefore(firstRuby);
    else range.setStart(a.node, a.offset);
    if (lastRuby && b.offset === b.node.length) range.setEndAfter(lastRuby);
    else range.setEnd(b.node, b.offset);
    return range;
  }
  function segments(text, granularity) {
    if (window.Intl && Intl.Segmenter) {
      var segmenter = segmenters[granularity] || (segmenters[granularity] = new Intl.Segmenter('ja', { granularity: granularity }));
      return Array.from(segmenter.segment(text));
    }
    var found = [], regex = granularity === 'word' ? /[\p{Script=Han}々〻]+/gu : /[^。！？!?]+[。！？!?]*[」』）)】〉》〕｝}］\]]*/gu;
    var match;
    while ((match = regex.exec(text))) found.push({ index: match.index, segment: match[0], isWordLike: true });
    return found;
  }
  function wrapWords() {
    runs().forEach(function (run) {
      var words = segments(run.text, 'word').filter(function (word) {
        return word.isWordLike && /[\p{Script=Han}々〻]/u.test(word.segment);
      });
      var rubySpans = new Map();
      run.nodes.forEach(function (item) {
        var ruby = item.node.parentElement.closest('ruby');
        if (!ruby) return;
        var previous = rubySpans.get(ruby);
        rubySpans.set(ruby, { start: previous ? Math.min(previous.start, item.start) : item.start,
          end: item.start + item.node.length });
      });
      words = words.map(function (word) {
        var start = word.index, end = start + word.segment.length;
        rubySpans.forEach(function (ruby) {
          if (ruby.start < end && ruby.end > start) { start = Math.min(start, ruby.start); end = Math.max(end, ruby.end); }
        });
        return { index: start, segment: run.text.slice(start, end) };
      }).reduce(function (merged, word) {
        var previous = merged[merged.length - 1];
        if (previous && word.index < previous.index + previous.segment.length) {
          previous.segment = run.text.slice(previous.index, Math.max(previous.index + previous.segment.length, word.index + word.segment.length));
        } else merged.push(word);
        return merged;
      }, []);
      // Descending ranges retain offsets in the untouched prefix of every original text node.
      words.reverse().forEach(function (word) {
        var range = boundaryRange(run, word.index, word.index + word.segment.length);
        var holder = document.createElement('span'); holder.className = 'hoshi-word';
        holder.appendChild(range.extractContents());
        holder.querySelectorAll('[id]').forEach(function (copy) {
          var original = document.getElementById(copy.id);
          if (original && original !== copy) copy.removeAttribute('id');
        });
        range.insertNode(holder);
      });
    });
    document.querySelectorAll('ruby').forEach(function (ruby) {
      if (ruby.closest('.hoshi-word')) return;
      var holder = document.createElement('span'); holder.className = 'hoshi-word';
      ruby.before(holder); holder.appendChild(ruby);
    });
    var bodyStyle = getComputedStyle(document.body);
    var vertical = bodyStyle.writingMode.indexOf('vertical') === 0;
    var available = vertical
      ? window.innerHeight - parseFloat(bodyStyle.paddingTop) - parseFloat(bodyStyle.paddingBottom)
      : window.innerWidth - parseFloat(bodyStyle.paddingLeft) - parseFloat(bodyStyle.paddingRight);
    document.querySelectorAll('.hoshi-word').forEach(function (word) {
      var rect = word.getBoundingClientRect(), inlineSize = vertical ? rect.height : rect.width;
      if (inlineSize > available && available > 0) {
        // A whole word or unusually long reading must remain accessible in a narrow pane.
        word.style.fontSize = (parseFloat(getComputedStyle(word).fontSize) * available / inlineSize) + 'px';
      }
    });
  }
  function insertMarker(location, marker, after) {
    var range = workingRange, word = location.node.parentElement.closest('.hoshi-word');
    if (word) {
      range.selectNodeContents(word);
      if (after) range.setStart(location.node, location.offset);
      else range.setEnd(location.node, location.offset);
      var remaining = range.cloneContents();
      remaining.querySelectorAll('rt,rp').forEach(function (node) { node.remove(); });
      if (!remaining.textContent.length) {
        if (after) word.after(marker); else word.before(marker); return;
      }
    }
    range = workingRange; range.setStart(location.node, location.offset); range.collapse(true); range.insertNode(marker);
  }
  function sentenceAnchors() {
    var sentences = [];
    runs().forEach(function (run) {
      var parts = segments(run.text, 'sentence');
      for (var i = parts.length - 1; i >= 0; i--) {
        var part = parts[i];
        if (!part.segment.trim()) continue;
        var a = point(run, part.index), b = point(run, part.index + part.segment.length);
        var end = document.createElement('span'), start = document.createElement('span');
        end.className = 'hoshi-sentence-end'; start.className = 'hoshi-sentence-start';
        end.setAttribute('aria-hidden', 'true'); start.setAttribute('aria-hidden', 'true');
        insertMarker(b, end, true);
        insertMarker(a, start, false);
        sentences.push({ start: start, end: end, text: part.segment });
      }
    });
    return sentences.sort(function (a, b) { return a.start.compareDocumentPosition(b.start) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1; });
  }
  function pages(sentence, context) {
    var range = workingRange; range.setStart(sentence.start, 0); range.setEnd(sentence.end, 0);
    var scroll = context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
    var first = Infinity, last = -Infinity;
    Array.from(range.getClientRects()).forEach(function (rect) {
      if (rect.width <= 0 || rect.height <= 0) return;
      var a = (context.vertical ? rect.top : rect.left) + scroll;
      var b = (context.vertical ? rect.bottom : rect.right) + scroll;
      first = Math.min(first, Math.floor((a + 0.5) / context.pageSize));
      last = Math.max(last, Math.floor((b - 0.5) / context.pageSize));
    });
    return { first: first, last: last };
  }
  window.hoshiTextLayout = {
    prepare: function (reader, paginated) {
      if (this.prepared) return;
      this.prepared = true;
      var before = runs().map(function (run) { return run.text; }).join('');
      wrapWords();
      this.sentences = paginated ? sentenceAnchors() : [];
      if (paginated) {
        var context = reader.getScrollContext();
        // Read all geometry before mutating: a layout after every sentence freezes long chapters.
        // Earlier valid breaks may remain after later passes; every fitting sentence stays whole.
        while (true) {
          var crossing = this.sentences.filter(function (sentence) {
            if (sentence.start.dataset.oversized === 'true' || sentence.start.classList.contains('hoshi-sentence-page-break')) return false;
            var initial = pages(sentence, context);
            return initial.first !== initial.last && isFinite(initial.first);
          });
          if (!crossing.length) break;
          crossing.forEach(function (sentence) { sentence.start.classList.add('hoshi-sentence-page-break'); });
          var oversized = crossing.filter(function (sentence) {
            var moved = pages(sentence, context);
            return moved.first !== moved.last;
          });
          oversized.forEach(function (sentence) {
            // An oversized sentence needs ordinary continuation, not a blank initial page.
            sentence.start.classList.remove('hoshi-sentence-page-break');
            sentence.start.dataset.oversized = 'true';
          });
        }
      }
      this.textPreserved = before === runs().map(function (run) { return run.text; }).join('');
    }
  };
})();

""".trimIndent()
