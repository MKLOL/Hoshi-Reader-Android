package moe.antimony.hoshi.features.reader

/** iOS-parity DOM anchoring for pre-translated EPUB sentences. */
internal object ReaderTranslationScripts {
    val css: String = """
        .hoshi-tl {
          display: inline-block; position: relative; width: .6em; height: .6em;
          margin-inline-start: 0; margin-inline-end: .45em;
          border: .07em solid currentColor; border-radius: 50%; opacity: .4;
          cursor: pointer; -webkit-user-select: none; user-select: none;
        }
        .hoshi-tl-wide { margin-inline-start: -.36em; }
        .hoshi-tl-narrow { margin-inline-start: -.06em; }
        .hoshi-tl::before {
          content: ''; position: absolute; left: 50%; top: 50%; width: .18em; height: .18em;
          margin: -.09em 0 0 -.09em; border-radius: 50%; background: currentColor;
        }
        .hoshi-tl::after { content: ''; position: absolute; inset: -.7em; }
    """.trimIndent()

    fun source(): String = """
        window.hoshiTranslations = {
          buttons: [],
          trailingChars: '。、！？…‥」』）)】〉》〕｝}］]',
          wideTrailing: '。、」』）)】〉》〕｝}］]',
          narrowTrailing: '！？!?…‥',
          scanChapter: function() {
            var chars = [], ends = [], walker = window.hoshiReader.createWalker(), node;
            while (node = walker.nextNode()) {
              var text = node.textContent;
              for (var i = 0; i < text.length;) {
                var ch = String.fromCodePoint(text.codePointAt(i));
                var next = i + ch.length;
                if (window.hoshiReader.isMatchableChar(ch)) {
                  chars.push(ch); ends.push({ node: node, offset: next });
                }
                i = next;
              }
            }
            return { chars: chars, ends: ends };
          },
          resolve: function(chapter, anchor) {
            var want = anchor.text || '', start = Number(anchor.start), len = Number(anchor.len);
            if (!want || !Number.isSafeInteger(start) || start < 0 ||
                !Number.isSafeInteger(len) || len <= 0) return -1;
            var wanted = Array.from(want);
            if (wanted.length !== len || start > chapter.chars.length - len) return -1;
            for (var i = 0; i < len; i++) if (chapter.chars[start + i] !== wanted[i]) return -1;
            return start + len - 1;
          },
          extendPastPunctuation: function(position) {
            var walker = window.hoshiReader.createWalker(), node = position.node, offset = position.offset;
            walker.currentNode = node;
            for (;;) {
              var text = node.textContent;
              while (offset < text.length) {
                var ch = String.fromCodePoint(text.codePointAt(offset));
                if (this.trailingChars.indexOf(ch) < 0) return { node: node, offset: offset };
                offset += ch.length;
              }
              var next = walker.nextNode();
              if (!next) return { node: node, offset: offset };
              var nextText = next.textContent;
              if (nextText.length && this.trailingChars.indexOf(String.fromCodePoint(nextText.codePointAt(0))) < 0) {
                return { node: node, offset: offset };
              }
              node = next; offset = 0;
            }
          },
          charBefore: function(position) {
            var text = position.node.textContent;
            if (position.offset > 0) return text[position.offset - 1];
            var walker = window.hoshiReader.createWalker(); walker.currentNode = position.node;
            var previous = walker.previousNode();
            return previous && previous.textContent.length ? previous.textContent[previous.textContent.length - 1] : '';
          },
          makeButton: function(id, position) {
            var button = document.createElement('span'), preceding = this.charBefore(position), kind = '';
            if (this.wideTrailing.indexOf(preceding) >= 0) kind = ' hoshi-tl-wide';
            else if (this.narrowTrailing.indexOf(preceding) >= 0) kind = ' hoshi-tl-narrow';
            button.className = 'hoshi-tl' + kind; button.setAttribute('role', 'button');
            button.dataset.tl = id; return button;
          },
          apply: function(anchors) {
            this.clear(); if (!anchors || !anchors.length) return 0;
            var chapter = this.scanChapter(); if (!chapter.ends.length) return 0;
            var placements = [];
            for (var a of anchors) {
              var last = this.resolve(chapter, a);
              if (last >= 0 && last < chapter.ends.length) {
                placements.push({ id: a.id, position: this.extendPastPunctuation(chapter.ends[last]) });
              }
            }
            for (var i = placements.length - 1; i >= 0; i--) {
              var placement = placements[i], button = this.makeButton(placement.id, placement.position);
              try {
                var range = document.createRange();
                range.setStart(placement.position.node, Math.min(placement.position.offset, placement.position.node.length));
                range.collapse(true); range.insertNode(button); this.buttons.push(button);
              } catch (e) {}
            }
            window.hoshiReader.buildNodeOffsets(); return this.buttons.length;
          },
          clear: function() {
            for (var button of this.buttons) {
              var parent = button.parentNode;
              if (parent) { parent.removeChild(button); parent.normalize(); }
            }
            this.buttons = [];
          }
        };
    """.trimIndent()
}
