package moe.antimony.hoshi.features.reader

/** Word-aware wrapping and sentence-aware page boundaries; visible source text stays unchanged. */
internal fun readerTextLayoutScript(): String = """
(function () {
  'use strict';
  // Reuse one live Range: thousands of detached ranges make every later DOM mutation quadratic.
  // Park it in a detached node while editing the page, so no mutation has to update its
  // boundary offsets (each update walks the children of a possibly very long paragraph).
  var workingRange = document.createRange();
  var parking = document.createTextNode('');
  function park() { workingRange.setStart(parking, 0); workingRange.collapse(true); }
  park();
  var segmenters = {};
  var SKIP = 'rt,rp,script,style,noscript,svg,math,textarea,pre,code';
  var BLOCK = 'p,div,li,dd,dt,blockquote,figcaption,h1,h2,h3,h4,h5,h6,td,th,section,article';
  var VISIBLE_EMPTY = 'img,svg,video,audio,canvas,iframe,object,embed,br,hr,input,.hoshi-tl';
  var MEDIA = 'img,svg,video,canvas,iframe,object,embed';
  var MARKER_BREAK = 'hoshi-sentence-page-break', BLOCK_BREAK = 'hoshi-sentence-block-break';
  // Each pass costs one chapter layout; the model normally settles within a few.
  var MAX_PASSES = 8;
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
  function isEmptyNode(node) {
    return node.nodeType === Node.COMMENT_NODE || (node.nodeType === Node.TEXT_NODE && !node.nodeValue.length);
  }
  function onlyEmptySiblings(node, forward) {
    for (var sibling = forward ? node.nextSibling : node.previousSibling; sibling;
      sibling = forward ? sibling.nextSibling : sibling.previousSibling) {
      if (!isEmptyNode(sibling)) return false;
    }
    return true;
  }
  // First and last nodes of a word, splitting text at its edges and keeping a ruby whole.
  function wordNodes(run, start, end) {
    var a = point(run, start), b = point(run, end, true), first, last;
    var firstRuby = a.node.parentElement.closest('ruby');
    var lastRuby = b.node.parentElement.closest('ruby');
    // Split the end first: splitting the start would move the end offset into a new node.
    if (lastRuby && b.offset === b.node.length) last = lastRuby;
    else {
      if (b.offset < b.node.length) b.node.splitText(b.offset);
      last = b.node;
    }
    if (firstRuby && a.offset === 0 && firstRuby.textContent) first = firstRuby;
    else if (a.offset > 0) {
      first = a.node.splitText(a.offset);
      if (last === a.node) last = first;
    } else first = a.node;
    if (first !== last && first.contains(last)) last = first;
    else if (first !== last && last.contains(first)) first = last;
    return { first: first, last: last };
  }
  // Move whole sibling nodes into the holder. Only a word that ends part-way through a
  // publisher element needs Range's partial-element cloning.
  function wrapNodes(first, last, holder) {
    var common = first === last ? first.parentNode : last.parentNode;
    while (!common.contains(first)) common = common.parentNode;
    while (first.parentNode !== common && onlyEmptySiblings(first, false)) first = first.parentNode;
    while (last.parentNode !== common && onlyEmptySiblings(last, true)) last = last.parentNode;
    if (first.parentNode === common && last.parentNode === common) {
      common.insertBefore(holder, first);
      for (var node = first; node;) {
        var next = node.nextSibling;
        holder.appendChild(node);
        if (node === last) break;
        node = next;
      }
      return;
    }
    var range = workingRange;
    if (first.nodeType === Node.TEXT_NODE) range.setStart(first, 0); else range.setStartBefore(first);
    if (last.nodeType === Node.TEXT_NODE) range.setEnd(last, last.length); else range.setEndAfter(last);
    holder.appendChild(range.extractContents());
    holder.querySelectorAll('[id]').forEach(function (copy) {
      var original = document.getElementById(copy.id);
      if (original && original !== copy) copy.removeAttribute('id');
    });
    range.insertNode(holder);
    park();
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
      // Descending words retain offsets in the untouched prefix of every original text node.
      words.reverse().forEach(function (word) {
        var nodes = wordNodes(run, word.index, word.index + word.segment.length);
        var holder = document.createElement('span'); holder.className = 'hoshi-word';
        wrapNodes(nodes.first, nodes.last, holder);
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
  function emptySibling(node) {
    return isEmptyNode(node) ||
      (node.nodeType === Node.ELEMENT_NODE && node.matches('rt,rp,.hoshi-sentence-start,.hoshi-sentence-end'));
  }
  function blankBeforeBlockText(node) {
    return emptySibling(node) || (node.nodeType === Node.ELEMENT_NODE && !node.textContent &&
      !node.matches(VISIBLE_EMPTY) && !node.querySelector(VISIBLE_EMPTY));
  }
  // Inserts a sentence marker; returns true when a start marker opens its block's content.
  function insertMarker(location, marker, after, block) {
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
    var opensBlock = false;
    if (!after && edge && block !== document.body && boundary.parentElement === block) {
      var previous = boundary.previousSibling;
      while (previous && blankBeforeBlockText(previous)) previous = previous.previousSibling;
      opensBlock = !previous;
    }
    if (boundary !== location.node) {
      if (after) boundary.after(marker); else boundary.before(marker);
    } else if (location.offset === 0) {
      location.node.before(marker);
    } else if (location.offset === location.node.length) {
      location.node.after(marker);
    } else {
      location.node.splitText(location.offset).before(marker);
    }
    return opensBlock;
  }
  function sentenceAnchors() {
    var sentences = [];
    runs().forEach(function (run, runIndex) {
      var parts = segments(run.text, 'sentence'), runSentences = [];
      var blockBreaks = run.block !== document.body &&
        /^(block|flow-root|list-item|flex|grid|table)$/.test(getComputedStyle(run.block).display);
      for (var i = parts.length - 1; i >= 0; i--) {
        var part = parts[i];
        if (!part.segment.trim()) continue;
        var a = point(run, part.index), b = point(run, part.index + part.segment.length, true);
        var end = document.createElement('span'), start = document.createElement('span');
        end.className = 'hoshi-sentence-end'; start.className = 'hoshi-sentence-start';
        end.setAttribute('aria-hidden', 'true'); start.setAttribute('aria-hidden', 'true');
        insertMarker(b, end, true, run.block);
        var opensBlock = insertMarker(a, start, false, run.block);
        // A sentence that opens its block breaks before the block itself: a block marker
        // inside the paragraph would make the following text a continuation line and drop
        // the paragraph's first-line indent.
        runSentences.push({ start: start, end: end, text: part.segment, block: run.block, run: runIndex, opensBlock: opensBlock,
          breakTarget: opensBlock && blockBreaks ? run.block : start,
          breakClass: opensBlock && blockBreaks ? BLOCK_BREAK : MARKER_BREAK });
      }
      // Runs already follow document order, so reverse each run's insertion order directly.
      runSentences.reverse().forEach(function (sentence) { sentences.push(sentence); });
    });
    return sentences;
  }
  function hasBreak(sentence) { return sentence.breakTarget.classList.contains(sentence.breakClass); }
  function setBreak(sentence, on) {
    if (on) sentence.breakTarget.classList.add(sentence.breakClass);
    else sentence.breakTarget.classList.remove(sentence.breakClass);
  }
  function isOversized(sentence) { return sentence.start.dataset.oversized === 'true'; }
  // The column box along the block axis: lines advance down (horizontal) or leftwards (vertical).
  function columnGeometry(context) {
    var style = getComputedStyle(document.body), body = document.body;
    if (context.vertical) {
      var right = body.clientWidth - parseFloat(style.paddingRight);
      return { origin: right, capacity: right - parseFloat(style.paddingLeft), inlineOrigin: parseFloat(style.paddingTop),
        inlineSize: body.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom) };
    }
    var top = parseFloat(style.paddingTop);
    return { origin: top, capacity: body.clientHeight - parseFloat(style.paddingBottom) - top, inlineOrigin: parseFloat(style.paddingLeft),
      inlineSize: body.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) };
  }
  function boxFor(rect, context, geometry, scroll) {
    var a = (context.vertical ? rect.top : rect.left) + scroll;
    var b = (context.vertical ? rect.bottom : rect.right) + scroll;
    var page = Math.floor((a + 0.5) / context.pageSize);
    return {
      page: page,
      lastPage: Math.floor((b - 0.5) / context.pageSize),
      inlineStart: a - page * context.pageSize - geometry.inlineOrigin,
      inlineEnd: b - page * context.pageSize - geometry.inlineOrigin,
      start: context.vertical ? geometry.origin - rect.right : rect.top - geometry.origin,
      end: context.vertical ? geometry.origin - rect.left : rect.bottom - geometry.origin
    };
  }
  function measure(sentence, context, geometry, scroll, withBoxes) {
    var range = workingRange; range.setStart(sentence.start, 0); range.setEnd(sentence.end, 0);
    var rects = range.getClientRects(), first = Infinity, last = -Infinity, boxes = withBoxes ? [] : null;
    for (var i = 0; i < rects.length; i++) {
      var rect = rects[i];
      if (rect.width <= 0 || rect.height <= 0) continue;
      var box = boxFor(rect, context, geometry, scroll);
      first = Math.min(first, box.page); last = Math.max(last, box.lastPage);
      if (boxes) boxes.push(box);
    }
    return { first: first, last: last, boxes: boxes };
  }
  function blockMargin(element, end, vertical) {
    var style = getComputedStyle(element);
    return parseFloat(vertical ? (end ? style.marginLeft : style.marginRight) : (end ? style.marginBottom : style.marginTop)) || 0;
  }
  // Reads the unbroken chapter once and lays its lines end to end along the block axis: each
  // page's unused tail is removed and a paragraph margin truncated at a page boundary is
  // restored. Lines are numbered in reading order; ruby annotations are smaller boxes on the
  // same line. Positions in this flow do not depend on breaks applied later.
  function naturalFlow(sentences, context, geometry, media) {
    var scroll = context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
    var used = {}, tops = {}, opener = {}, lastPage = -1;
    function note(box, index) {
      used[box.page] = Math.max(used[box.page] === undefined ? -Infinity : used[box.page], box.end);
      if (tops[box.page] === undefined || box.start < tops[box.page]) { tops[box.page] = box.start; opener[box.page] = index; }
      lastPage = Math.max(lastPage, box.page);
    }
    var measured = sentences.map(function (sentence, index) {
      var measurement = measure(sentence, context, geometry, scroll, true);
      measurement.boxes.forEach(function (box) { note(box, index); });
      return measurement;
    });
    var mediaBoxes = [];
    media.forEach(function (element) {
      var rect = element.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      var box = boxFor(rect, context, geometry, scroll);
      note(box, -1); mediaBoxes.push(box);
    });
    var sizes = [], textEnd = -Infinity;
    for (var sample = 0; sample < measured.length && sizes.length < 512; sample++) {
      measured[sample].boxes.forEach(function (box) { sizes.push(box.end - box.start); });
    }
    sizes.sort(function (x, y) { return x - y; });
    var textSize = sizes.length ? sizes[sizes.length >> 1] : 0;
    measured.forEach(function (measurement) {
      measurement.boxes.forEach(function (box) { textEnd = Math.max(textEnd, box.end); });
    });
    var textBoxes = [];
    measured.forEach(function (measurement) {
      measurement.boxes.forEach(function (box) { if (box.end - box.start >= textSize * 0.75) textBoxes.push(box); });
    });
    textBoxes.sort(function (x, y) { return x.page - y.page || x.end - y.end; });
    var ordinal = -1, previousBox = null, lineEnds = [], advances = [];
    textBoxes.forEach(function (box) {
      if (!previousBox || box.page !== previousBox.page || box.end - previousBox.end > 3) {
        if (previousBox && box.page === previousBox.page) advances.push(box.end - previousBox.end);
        lineEnds[++ordinal] = 0;
      }
      box.ordinal = ordinal; previousBox = box;
      lineEnds[ordinal] = Math.max(lineEnds[ordinal], box.inlineEnd);
    });
    advances.sort(function (x, y) { return x - y; });
    // Below a page's last line there is only half the leading. The closest plain lines show
    // the leading; the lowest text any page already holds is known to fit.
    var plainAdvance = advances.length ? advances[Math.floor(advances.length * 0.1)] : textSize;
    var bottom = Math.min(geometry.capacity, Math.max(isFinite(textEnd) ? textEnd : 0,
      geometry.capacity - Math.max(0, plainAdvance - textSize) / 2));
    // Above a page's first line sits the same half leading, unless the line starts with a
    // ruby annotation; only plain first lines show it.
    var pageTops = [];
    for (var page = 1; page <= lastPage; page++) if (tops[page] > 0.5) pageTops.push(tops[page]);
    pageTops.sort(function (x, y) { return x - y; });
    var lead = pageTops.length ? Math.min(pageTops[pageTops.length >> 1], geometry.capacity / 4) : geometry.capacity - bottom;
    var flow = [0];
    for (page = 0; page <= lastPage; page++) {
      var opening = sentences[opener[page + 1]];
      flow[page + 1] = flow[page] + (used[page] === undefined ? 0 : used[page] + geometry.capacity - bottom) + (opening ? opening.gapBefore : 0);
    }
    sentences.forEach(function (sentence, index) {
      var first = null, last = null, top = null;
      var lines = measured[index].boxes.map(function (box) {
        if (!top || box.page < top.page || (box.page === top.page && box.start < top.start - 0.5)) top = box;
        if (box.ordinal !== undefined) {
          if (!first || box.ordinal < first.ordinal || (box.ordinal === first.ordinal && box.inlineStart < first.inlineStart)) first = box;
          if (!last || box.ordinal > last.ordinal || (box.ordinal === last.ordinal && box.inlineEnd > last.inlineEnd)) last = box;
        }
        return [flow[box.page] + box.start, flow[box.page] + box.end];
      });
      lines.sort(function (x, y) { return x[0] - y[0]; });
      sentence.lines = lines;
      sentence.flowStart = lines.length ? lines[0][0] : NaN;
      // How far below a page's top this sentence's first box sits when it opens the page.
      sentence.inset = top && top.end - top.start < textSize * 0.75 ? 0 : lead;
      sentence.flowEnd = lines.reduce(function (value, line) { return Math.max(value, line[1]); }, -Infinity);
      // Where the sentence begins and ends along numbered lines, to estimate rewrapping.
      sentence.firstLine = first ? first.ordinal : null;
      sentence.lastLine = last ? last.ordinal : null;
      sentence.inlineStart = first ? first.inlineStart : 0;
      sentence.inlineEnd = last ? last.inlineEnd : 0;
    });
    // Lines hold whole words, so they usually end short of the column; full lines show how much.
    lineEnds.sort(function (x, y) { return x - y; });
    return {
      lead: lead,
      bottom: bottom,
      textSize: textSize,
      // The usual distance between lines, ruby included.
      line: advances.length ? advances[advances.length >> 1] : textSize + 2 * lead,
      lineFill: lineEnds.length ? Math.min(geometry.inlineSize, lineEnds[Math.floor(lineEnds.length * 0.75)]) : 0,
      media: mediaBoxes.map(function (box) { return [flow[box.page] + box.start, flow[box.page] + box.end]; })
        .sort(function (x, y) { return x[0] - y[0]; })
    };
  }
  // One pass reads the layout from where the previous pass first changed a break, since
  // nothing before that point moved. While the breaks in place are kept, decisions follow the
  // real layout exactly. After a change, the natural flow predicts the content it moves until
  // the next break that stays: a forced break makes everything after it independent of the
  // rest. An existing break is only dropped when its sentence fits with a line to spare, so
  // near calls cannot flip the same break back and forth between passes.
  function planPass(sentences, context, geometry, natural, resume) {
    var scroll = context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
    var lead = natural.lead, bottom = natural.bottom, tolerance = 0.5;
    var synced = true, page = resume.page, pageTop = resume.pageTop, nextMedia = resume.nextMedia;
    var rewrap = resume.rewrap && { run: resume.rewrap.run, line: resume.rewrap.line, offset: resume.rewrap.offset, tail: resume.rewrap.tail };
    var changes = 0, next = null, before = null, actual = [];
    // Read every position before changing a break: a read after a change lays out the chapter.
    for (var read = resume.index; read < sentences.length; read++) {
      actual[read] = sentences[read].lines.length ? measure(sentences[read], context, geometry, scroll, false) : null;
    }
    function change(sentence, on) {
      setBreak(sentence, on);
      if (!changes++) next = before;
    }
    function continueLines(sentence) {
      // A sentence longer than a page continues line by line.
      rewrap = null;
      sentence.lines.forEach(function (line) {
        if (line[1] > pageTop + bottom + tolerance) pageTop = line[0] - lead;
      });
    }
    // A page that starts at a sentence. A forced break in mid-line starts that sentence on a
    // new line, so the rest of its paragraph rewraps and can close up by a line.
    function anchor(sentence) {
      pageTop = sentence.flowStart - sentence.inset;
      var offset = sentence.breakClass === MARKER_BREAK && hasBreak(sentence) ? sentence.inlineStart : 0;
      rewrap = offset > tolerance && sentence.firstLine !== null && natural.lineFill > 0
        ? { run: sentence.run, line: sentence.firstLine, offset: offset, tail: 0 } : null;
    }
    // Lines a position moves up once its paragraph rewraps; rounded towards keeping the line,
    // since an early break only shortens a page while a late one costs another pass.
    function closedLines(line, inline) {
      if (line === null) return 0;
      var lines = line - rewrap.line, fill = natural.lineFill;
      return Math.max(0, lines - Math.floor((lines * fill + inline - rewrap.offset + natural.textSize) / fill));
    }
    for (var index = resume.index; index < sentences.length; index++) {
      var sentence = sentences[index], measurement = actual[index];
      if (!measurement || !isFinite(measurement.first)) continue;
      if (!changes) {
        before = { index: index, page: page, pageTop: pageTop, nextMedia: nextMedia,
          rewrap: rewrap && { run: rewrap.run, line: rewrap.line, offset: rewrap.offset, tail: rewrap.tail } };
      }
      var start = sentence.flowStart, end = sentence.flowEnd;
      if (rewrap && rewrap.run !== sentence.run) {
        pageTop += rewrap.tail;
        rewrap = null;
      }
      if (rewrap) {
        start -= closedLines(sentence.firstLine, sentence.inlineStart) * natural.line;
        rewrap.tail = closedLines(sentence.lastLine, sentence.inlineEnd - tolerance) * natural.line;
        end -= rewrap.tail;
      }
      while (nextMedia < natural.media.length && natural.media[nextMedia][0] < start) {
        var item = natural.media[nextMedia++];
        if (!synced && item[1] > pageTop + geometry.capacity + tolerance) pageTop = item[0];
      }
      var fits = end <= pageTop + bottom + tolerance;
      var fitsWithRoom = end + natural.line <= pageTop + bottom;
      var fitsPage = !isOversized(sentence) && end - start <= bottom - sentence.inset + tolerance;
      var crosses = measurement.first !== measurement.last;
      if (synced) {
        if (crosses && hasBreak(sentence)) {
          // An oversized sentence needs ordinary continuation, not a blank initial page.
          change(sentence, false);
          sentence.start.dataset.oversized = 'true';
          synced = false;
          continueLines(sentence);
        } else if (crosses && !isOversized(sentence)) {
          change(sentence, true);
          sentence.required = true;
          synced = false;
          anchor(sentence);
        } else if (measurement.first > page) {
          if (hasBreak(sentence) && !sentence.required && fitsWithRoom) {
            change(sentence, false);
            synced = false;
          } else {
            anchor(sentence);
            page = measurement.first;
            if (crosses) { continueLines(sentence); page = measurement.last; }
          }
        } else if (crosses) {
          continueLines(sentence);
          page = measurement.last;
        }
      } else if (hasBreak(sentence) && (sentence.required || (!fitsWithRoom && fitsPage))) {
        synced = true;
        anchor(sentence);
        page = measurement.first;
        if (crosses) { continueLines(sentence); page = measurement.last; }
      } else if (fits) {
        if (hasBreak(sentence)) change(sentence, false);
      } else if (fitsPage) {
        change(sentence, true);
        anchor(sentence);
      } else {
        if (hasBreak(sentence)) change(sentence, false);
        continueLines(sentence);
      }
    }
    return next;
  }
  function placeSentenceBreaks(sentences, context) {
    var geometry = columnGeometry(context);
    if (!(geometry.capacity > 0) || !(context.pageSize > 0)) return;
    var media = Array.from(document.querySelectorAll(MEDIA)).filter(function (element) {
      return getComputedStyle(element).display.indexOf('inline') !== 0;
    });
    // Natural margins between paragraphs, read before any break truncates or clears them.
    sentences.forEach(function (sentence, index) {
      var previous = sentences[index - 1];
      sentence.gapBefore = sentence.opensBlock && previous && previous.block !== sentence.block
        ? Math.max(0, blockMargin(previous.block, true, context.vertical), blockMargin(sentence.block, false, context.vertical))
        : 0;
    });
    var natural = naturalFlow(sentences, context, geometry, media);
    var passes = 0, resume = { index: 0, page: -1, pageTop: 0, nextMedia: 0, rewrap: null };
    while (passes < MAX_PASSES && (resume = planPass(sentences, context, geometry, natural, resume))) passes++;
    if (resume) {
      // The estimate kept disagreeing with the layout. Finish exactly, one page at a time from
      // where it last changed: drop its later estimates, then break at each crossing sentence.
      var scroll = context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
      for (var index = resume.index; index < sentences.length; index++) setBreak(sentences[index], false);
      for (var guard = 0, from = resume.index; from >= 0 && guard < sentences.length; guard++) {
        var settled = -1;
        for (index = from; index < sentences.length && settled < 0; index++) {
          var sentence = sentences[index];
          if (isOversized(sentence) || !sentence.lines.length) continue;
          var measurement = measure(sentence, context, geometry, scroll, false);
          if (isFinite(measurement.first) && measurement.first !== measurement.last) settled = index;
        }
        if (settled >= 0) {
          sentence = sentences[settled];
          if (hasBreak(sentence)) {
            setBreak(sentence, false);
            sentence.start.dataset.oversized = 'true';
          } else {
            setBreak(sentence, true);
          }
        }
        from = settled;
        passes++;
      }
    }
    park();
    window.hoshiTextLayout.passes = passes;
  }
  function visibleText() { return runs().map(function (run) { return run.text; }).join(''); }
  var textBefore = null;
  window.hoshiTextLayout = {
    prepare: function (reader, paginated) {
      if (this.prepared) return;
      this.prepared = true;
      textBefore = visibleText();
      wrapWords();
      this.sentences = paginated ? sentenceAnchors() : [];
      if (paginated) placeSentenceBreaks(this.sentences, reader.getScrollContext());
    },
    // Checked on demand: reading the whole chapter again is only needed for verification.
    get textPreserved() { return textBefore !== null && textBefore === visibleText(); }
  };
})();

""".trimIndent()
