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
    var out = [], current = null, blocks = new WeakMap();
    function blockFor(element) {
      if (!element || element === document.body) return document.body;
      if (blocks.has(element)) return blocks.get(element);
      var display = getComputedStyle(element).display;
      var block = element.matches(BLOCK) || /^(block|flow-root|list-item|flex|grid|table|table-.+)$/.test(display)
        ? element : blockFor(element.parentElement);
      blocks.set(element, block);
      return block;
    }
    // A hard break or a replaced element separates authored lines, even when both sides
    // have the same paragraph parent. Never turn those lines into one inline word box.
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          if (node.matches('rt,rp')) return NodeFilter.FILTER_REJECT;
          if (node.matches(SKIP) || getComputedStyle(node).display === 'none') {
            current = null;
            return NodeFilter.FILTER_REJECT;
          }
          if (node.matches('br,hr,img,video,audio,canvas,iframe,object,embed')) {
            current = null;
            return NodeFilter.FILTER_REJECT;
          }
          if (blockFor(node) === node) current = null;
          return NodeFilter.FILTER_SKIP;
        }
        return node.nodeValue ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    var node;
    while ((node = walker.nextNode())) {
      var block = blockFor(node.parentElement);
      if (!current || current.block !== block) {
        current = { block: block, text: '', nodes: [] }; out.push(current);
      }
      current.nodes.push({ node: node, start: current.text.length });
      current.text += node.nodeValue;
    }
    return out;
  }
  function point(run, offset, endBoundary) {
    var low = 0, high = run.nodes.length;
    while (low < high) {
      var middle = (low + high) >>> 1;
      if (run.nodes[middle].start < offset || (!endBoundary && run.nodes[middle].start === offset)) low = middle + 1;
      else high = middle;
    }
    var item = run.nodes[Math.max(0, low - 1)];
    return { node: item.node, offset: Math.max(0, Math.min(offset - item.start, item.node.length)) };
  }
  function boundaryRange(run, start, end) {
    var a = point(run, start), b = point(run, end, true), range = workingRange;
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
  function insertMarker(location, marker, after, block) {
    function emptySibling(node) {
      return node.nodeType === Node.COMMENT_NODE ||
        (node.nodeType === Node.TEXT_NODE && !node.nodeValue.length) ||
        (node.nodeType === Node.ELEMENT_NODE && node.matches('rt,rp,.hoshi-sentence-start,.hoshi-sentence-end'));
    }
    // Keep forced column breaks outside a complete inline publisher element. Older WebView
    // versions can ignore a block break nested in an inline span. Replaced elements and hard
    // breaks still count as siblings even though their textContent is empty.
    var boundary = location.node, edge = location.offset === (after ? boundary.length : 0);
    while (edge && boundary.parentElement && boundary.parentElement !== document.body && boundary.parentElement !== block) {
      var parent = boundary.parentElement;
      var sibling = after ? boundary.nextSibling : boundary.previousSibling;
      while (sibling && emptySibling(sibling)) sibling = after ? sibling.nextSibling : sibling.previousSibling;
      if (sibling) break;
      boundary = parent;
    }
    // Avoid Range.insertNode's parent-offset bookkeeping over a paragraph with thousands of
    // siblings. Keep the shared measurement range on a text node while changing the DOM.
    workingRange.setStart(location.node, location.offset); workingRange.collapse(true);
    if (boundary !== location.node) {
      if (after) boundary.after(marker); else boundary.before(marker);
    } else if (location.offset === 0) {
      location.node.before(marker);
    } else if (location.offset === location.node.length) {
      location.node.after(marker);
    } else {
      location.node.splitText(location.offset).before(marker);
    }
  }
  function sentenceAnchors() {
    var sentences = [];
    runs().forEach(function (run) {
      var parts = segments(run.text, 'sentence'), runSentences = [];
      for (var i = parts.length - 1; i >= 0; i--) {
        var part = parts[i];
        if (!part.segment.trim()) continue;
        var a = point(run, part.index), b = point(run, part.index + part.segment.length, true);
        var end = document.createElement('span'), start = document.createElement('span');
        end.className = 'hoshi-sentence-end'; start.className = 'hoshi-sentence-start';
        end.setAttribute('aria-hidden', 'true'); start.setAttribute('aria-hidden', 'true');
        insertMarker(b, end, true, run.block);
        insertMarker(a, start, false, run.block);
        runSentences.push({ start: start, end: end, text: part.segment });
      }
      // Runs already follow document order, so reverse each run's insertion order directly.
      runSentences.reverse().forEach(function (sentence) { sentences.push(sentence); });
    });
    return sentences;
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
