#!/usr/bin/env python3
"""
manga_debug.py — deterministic manga-reader UI debugging over Chrome DevTools.

WHY THIS EXISTS
---------------
`adb shell input tap X Y` on the manga reader is unreliable: the OCR bubbles are
tiny, and screen pixels ≠ the coordinates you eyeball off a scaled screenshot, so
taps repeatedly miss the boxes. This tool drives the reader's WebView DIRECTLY
over the Chrome DevTools Protocol (CDP), using the page's own DOM coordinates —
no pixel guessing. It can:

  * list every OCR box with its CSS rect AND its on-screen device-pixel rect,
  * reveal / look up a box BY INDEX (calls the page's own hoshiManga.handleTap
    at the box's centre — the exact entry a real tap routes through),
  * report the live dictionary-popup overlay bounds (via uiautomator) and whether
    it overlaps the tapped box (the "popup covers the word" bug, measured),
  * annotate a screenshot with crosshairs so you can SEE where a coordinate lands.

Prereqs: emulator running the debug build (WebView debugging is enabled in debug
builds), `pip3 install websocket-client pillow`.

USAGE
-----
  python3 tools/manga_debug.py boxes                 # list OCR boxes (idx, text, rects)
  python3 tools/manga_debug.py reveal <idx>          # first tap: reveal a bubble
  python3 tools/manga_debug.py lookup <idx> [frac]   # reveal+lookup; frac=0..1 along box (default 0.12 = near start)
  python3 tools/manga_debug.py cover <idx>           # *** popup vs the box's rendered TEXT EXTENT
                                                     #     (Range, incl. overflow ！？); measures the
                                                     #     "popup covers the word" bug deterministically
  python3 tools/manga_debug.py frame <idx> [vert]    # computed popup frame vs box host-rect (LookupPopupLayout port)
  python3 tools/manga_debug.py overlap <idx>         # popup overlay bounds vs box border-box
  python3 tools/manga_debug.py eval '<js>'           # evaluate arbitrary JS in the page
  python3 tools/manga_debug.py annotate <png> x1 y1 [label] [x2 y2 label...]

`cover` is the key command: it takes the box's TRUE painted extent (a Range over
the box contents — which includes glyphs that overflow mokuro's fixed-height box)
and the popup's ACTUAL on-screen view bounds, and reports whether the popup covers
the text. The OCR box's getBoundingClientRect() can read "clear" while the popup
still sits on top of overflowing punctuation — `cover` catches exactly that.

For a full sweep across boxes/pages see tools/manga_popup_regression.py.

All box rects are printed in BOTH css px and device px. Device px = css * dpr
(+ WebView screen offset), which is what `adb input tap` and screenshots use.
"""
import json
import subprocess
import sys
import urllib.request

ADB = subprocess.run(
    ["bash", "-lc", "echo ${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"],
    capture_output=True, text=True,
).stdout.strip()
CDP_PORT = 9333


def sh(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True)


def _has_ocr_boxes(ws_url):
    import websocket
    try:
        ws = websocket.create_connection(ws_url, timeout=6, suppress_origin=True)
    except Exception:
        return False
    try:
        ws.send(json.dumps({"id": 9, "method": "Runtime.evaluate", "params": {
            "expression": "document.querySelectorAll('.ocr-box').length", "returnByValue": True}}))
        while True:
            m = json.loads(ws.recv())
            if m.get("id") == 9:
                return (m.get("result", {}).get("result", {}).get("value") or 0) > 0
    except Exception:
        return False
    finally:
        ws.close()


def _webview_socket():
    out = sh("shell", "cat", "/proc/net/unix").stdout
    socks = sorted({ln.split("@")[-1].strip() for ln in out.splitlines() if "webview_devtools_remote" in ln})
    # The reader's page WebView may load its mokuro HTML via data: URL OR
    # about:blank (loadDataWithBaseURL), so identify it by CONTENT — the page that
    # actually has `.ocr-box` elements — not by URL. Popup/other WebViews are skipped.
    for s in socks:
        sh("forward", "--remove-all")
        sh("forward", f"tcp:{CDP_PORT}", f"localabstract:{s}")
        try:
            pages = json.load(urllib.request.urlopen(f"http://localhost:{CDP_PORT}/json/list", timeout=6))
        except Exception:
            continue
        for p in pages:
            if p.get("type") == "page" and _has_ocr_boxes(p["webSocketDebuggerUrl"]):
                return p["webSocketDebuggerUrl"]
    raise SystemExit("No manga page WebView found. Is a mokuro page open in the reader?")


def cdp_eval(expr):
    import websocket  # lazy import so `annotate` works without it
    ws_url = _webview_socket()
    ws = websocket.create_connection(ws_url, timeout=12, suppress_origin=True)
    ws.send(json.dumps({
        "id": 1, "method": "Runtime.evaluate",
        "params": {"expression": expr, "returnByValue": True, "awaitPromise": True},
    }))
    try:
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == 1:
                res = msg.get("result", {})
                if "exceptionDetails" in res:
                    raise SystemExit("JS error: " + json.dumps(res["exceptionDetails"])[:600])
                return res.get("result", {}).get("value")
    finally:
        ws.close()


BOXES_JS = r"""
(function(){
  var dpr = window.devicePixelRatio || 1;
  var vv = window.visualViewport;
  var hostScale = (window.hoshiManga && window.hoshiManga.hostScale) ? window.hoshiManga.hostScale() : 1;
  var boxes = document.querySelectorAll('.ocr-box');
  var out = [];
  for (var i=0;i<boxes.length;i++){
    var b=boxes[i], r=b.getBoundingClientRect();
    out.push({
      idx:i,
      vertical:b.classList.contains('vertical'),
      revealed:b.classList.contains('revealed'),
      text:(b.textContent||'').replace(/\s+/g,' ').trim().slice(0,16),
      css:{x:r.x,y:r.y,w:r.width,h:r.height},
      dev:{x:Math.round(r.x*dpr),y:Math.round(r.y*dpr),w:Math.round(r.width*dpr),h:Math.round(r.height*dpr)}
    });
  }
  return JSON.stringify({dpr:dpr, hostScale:hostScale, out:out});
})()
"""


def _boxes():
    data = json.loads(cdp_eval(BOXES_JS))
    return data["out"], data["dpr"], data["hostScale"]


def cmd_boxes():
    boxes, dpr, hostScale = _boxes()
    print(f"dpr={dpr} hostScale={hostScale}  ({len(boxes)} OCR boxes)")
    for b in boxes:
        c, d = b["css"], b["dev"]
        flag = ("V" if b["vertical"] else "H") + ("R" if b["revealed"] else "-")
        print(f"[{b['idx']:2}] {flag} '{b['text']}'  css=({c['x']:.0f},{c['y']:.0f} {c['w']:.0f}x{c['h']:.0f})"
              f"  dev=({d['x']},{d['y']} {d['w']}x{d['h']})  dev-center=({d['x']+d['w']//2},{d['y']+d['h']//2})")


def _handle_tap(css_x, css_y, max_len=10):
    return cdp_eval(f"String(window.hoshiManga.handleTap({css_x}, {css_y}, {max_len}))")


def cmd_reveal(idx):
    boxes, _, _ = _boxes()
    b = boxes[int(idx)]["css"]
    cx, cy = b["x"] + b["w"] / 2, b["y"] + b["h"] / 2
    print("reveal ->", _handle_tap(cx, cy))


def cmd_lookup(idx, frac=0.12):
    boxes, _, _ = _boxes()
    box = boxes[int(idx)]
    b = box["css"]
    # For a vertical box the sentence starts at the TOP; for horizontal, at the LEFT.
    if box["vertical"]:
        cx, cy = b["x"] + b["w"] / 2, b["y"] + b["h"] * float(frac)
    else:
        cx, cy = b["x"] + b["w"] * float(frac), b["y"] + b["h"] / 2
    r1 = _handle_tap(cx, cy)
    if r1 == "__revealed__":
        r2 = _handle_tap(cx, cy)
        print(f"revealed, then lookup @css({cx:.0f},{cy:.0f}) -> selected '{r2}'")
    else:
        print(f"lookup @css({cx:.0f},{cy:.0f}) -> '{r1}'")


def _uiauto_popup_bounds():
    sh("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml = sh("shell", "cat", "/sdcard/uidump.xml").stdout
    # The lookup popup overlay hosts a WebView on a distinct node; grab all bounds
    # and return every rect that is NOT the full-screen reader WebView.
    import re
    rects = []
    for m in re.finditer(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups())
        rects.append((x1, y1, x2, y2))
    return rects


def _intersect(a, b):
    x1 = max(a[0], b[0]); y1 = max(a[1], b[1])
    x2 = min(a[2], b[2]); y2 = min(a[3], b[3])
    return max(0, x2 - x1) * max(0, y2 - y1)


def cmd_overlap(idx):
    boxes, _, _ = _boxes()
    d = boxes[int(idx)]["dev"]
    box_rect = (d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"])
    box_area = d["w"] * d["h"]
    print(f"box[{idx}] '{boxes[int(idx)]['text']}' dev-rect={box_rect} area={box_area}")
    for r in _uiauto_popup_bounds():
        area = _intersect(box_rect, r)
        # Only flag plausible popup rects: not the full screen, sizeable overlap.
        if area > 0 and (r[2] - r[0]) * (r[3] - r[1]) < box_area * 40:
            frac = area / box_area if box_area else 0
            print(f"  overlaps view {r}: intersection={area}px ({frac:.0%} of box)")


HOSTRECT_JS = r"""
(function(idx){
  var boxes = document.querySelectorAll('.ocr-box');
  var b = boxes[idx]; if(!b) return null;
  var r = b.getBoundingClientRect();
  var hr = (window.hoshiManga && window.hoshiManga.hostRectFromViewportRect)
    ? window.hoshiManga.hostRectFromViewportRect(r) : {x:r.x,y:r.y,width:r.width,height:r.height};
  var vv = window.visualViewport;
  return JSON.stringify({
    host:{x:hr.x,y:hr.y,w:hr.width,h:hr.height},
    // Visible viewport size in css px (== dp), the space the app lays the popup out in.
    screenW: (vv? vv.width*(window.hoshiManga?window.hoshiManga.hostScale():1) : window.innerWidth),
    screenH: (vv? vv.height*(window.hoshiManga?window.hoshiManga.hostScale():1) : window.innerHeight),
    vertical: b.classList.contains('vertical')
  });
})(IDX)
"""


def _popup_frame(rect, screenW, screenH, maxW=320.0, maxH=250.0, isVertical=False,
                 isFullWidth=False, topInset=0.0, bottomInset=0.0):
    """Faithful port of LookupPopupLayout.kt — MUST stay in sync with the Kotlin."""
    PAD, BORDER = 4.0, 6.0
    x, y, w, h = rect["x"], rect["y"], rect["w"], rect["h"]
    minX, maxX, minY, maxY = x, x + w, y, y + h
    spaceLeft = minX - PAD
    spaceRight = screenW - maxX - PAD
    spaceAbove = minY - topInset - PAD
    spaceBelow = screenH - bottomInset - maxY - PAD
    showOnRight = spaceRight >= spaceLeft or spaceRight >= maxW

    def clamp(v, lo, hi):
        return max(lo, min(v, hi))

    MIN = 120.0  # minPopupWidth == minPopupHeight in LookupPopupLayout.kt
    MIN_USABLE = 48.0
    if isFullWidth:
        width = screenW - BORDER * 2
    elif isVertical:
        available = max(spaceLeft, spaceRight) - BORDER
        width = min(available if available >= MIN_USABLE else MIN, maxW)
    else:
        width = min(screenW - BORDER * 2, maxW)
    if isFullWidth:
        height = min(maxH, max(screenH - topInset - bottomInset - BORDER * 2, 1.0))
    elif isVertical:
        height = maxH
    else:
        available = max(spaceAbove, spaceBelow) - BORDER
        height = min(available if available >= MIN_USABLE else MIN, maxH)

    if isFullWidth:
        cx = width / 2 + BORDER
        anchor = screenH - bottomInset - height / 2 - BORDER
        cy = clamp(anchor, height / 2 + topInset + BORDER, anchor)
    elif isVertical:
        raw = (maxX + PAD + width / 2) if showOnRight else (minX - PAD - width / 2)
        cx = clamp(raw, width / 2, screenW - width / 2)
        cy = clamp(minY + height / 2, height / 2 + BORDER + topInset,
                   screenH - bottomInset - height / 2 - BORDER)
    else:
        cx = clamp(minX + width / 2, width / 2 + BORDER, screenW - width / 2 - BORDER)
        showBelow = spaceBelow >= spaceAbove or spaceBelow >= maxH
        raw = (maxY + PAD + height / 2) if showBelow else (minY - PAD - height / 2)
        cy = clamp(raw, height / 2 + topInset + BORDER,
                   screenH - bottomInset - height / 2 - BORDER)
    return {"x": cx - width / 2, "y": cy - height / 2, "w": width, "h": height}


def cmd_frame(idx, isVertical=None):
    data = json.loads(cdp_eval(HOSTRECT_JS.replace("IDX", str(int(idx)))))
    rect, sw, sh = data["host"], data["screenW"], data["screenH"]
    vert = data["vertical"] if isVertical is None else (isVertical == "true")
    pf = _popup_frame(rect, sw, sh, isVertical=vert)
    bx = (rect["x"], rect["y"], rect["x"] + rect["w"], rect["y"] + rect["h"])
    px = (pf["x"], pf["y"], pf["x"] + pf["w"], pf["y"] + pf["h"])
    ix = max(0.0, min(bx[2], px[2]) - max(bx[0], px[0]))
    iy = max(0.0, min(bx[3], px[3]) - max(bx[1], px[1]))
    inter = ix * iy
    box_area = rect["w"] * rect["h"]
    print(f"screen={sw:.0f}x{sh:.0f}  vertical={vert}")
    print(f"box  host-rect (css/dp) = ({rect['x']:.0f},{rect['y']:.0f} {rect['w']:.0f}x{rect['h']:.0f})")
    print(f"popup frame            = ({pf['x']:.0f},{pf['y']:.0f} {pf['w']:.0f}x{pf['h']:.0f})")
    print(f"OVERLAP = {inter:.0f} css²  ({(inter/box_area if box_area else 0):.0%} of box)"
          + ("   <<< POPUP COVERS THE BOX" if inter > 0 else "   (clear)"))


TEXT_EXTENT_JS = r"""
(function(idx){
  var b=document.querySelectorAll('.ocr-box')[idx]; if(!b) return null;
  var dpr=window.devicePixelRatio||1;
  // The rendered text can OVERFLOW the fixed-height OCR box (revealed OCR text
  // is sometimes taller than mokuro's detected region, so trailing punctuation
  // like ！？ spills past the box edge). getBoundingClientRect() reflects only
  // the box's border-box, so use a Range over the box contents to get the TRUE
  // painted extent — text overflow AND the action buttons.
  var range=document.createRange(); range.selectNodeContents(b);
  var g=range.getBoundingClientRect();
  var br=b.getBoundingClientRect();
  function dev(r){return {x:Math.round(r.x*dpr),y:Math.round(r.y*dpr),right:Math.round(r.right*dpr),bottom:Math.round(r.bottom*dpr)};}
  return JSON.stringify({box:dev(br), text:dev(g), text_css:{y:Math.round(g.y),bottom:Math.round(g.bottom)}});
})(IDX)
"""


def _popup_view_rect():
    sh("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml = sh("shell", "cat", "/sdcard/uidump.xml").stdout
    import re
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(xml)
    except Exception:
        return None
    best = None
    for n in root.iter("node"):
        cls = n.get("class", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m or "WebView" not in cls:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        w, h = x2 - x1, y2 - y1
        # skip the full-screen reader WebView; the popup is a smaller card
        if w >= 1000 and h >= 2000:
            continue
        if best is None or w * h > best[4]:
            best = (x1, y1, x2, y2, w * h)
    return best[:4] if best else None


def cmd_cover(idx):
    data = json.loads(cdp_eval(TEXT_EXTENT_JS.replace("IDX", str(int(idx)))))
    tb, bx = data["text"], data["box"]
    popup = _popup_view_rect()
    print(f"box[{idx}] OCR-box rect (dev)     = ({bx['x']},{bx['y']})-({bx['right']},{bx['bottom']})")
    print(f"        rendered text extent (dev) = ({tb['x']},{tb['y']})-({tb['right']},{tb['bottom']})"
          f"   [overflow below box: {tb['bottom'] - bx['bottom']}px]")
    if not popup:
        print("        popup: NOT OPEN (no popup WebView found) — look it up first")
        return
    px = popup
    print(f"        popup view rect (dev)      = ({px[0]},{px[1]})-({px[2]},{px[3]})")
    for label, rect in (("OCR box", (bx["x"], bx["y"], bx["right"], bx["bottom"])),
                        ("TEXT extent", (tb["x"], tb["y"], tb["right"], tb["bottom"]))):
        ix = max(0, min(rect[2], px[2]) - max(rect[0], px[0]))
        iy = max(0, min(rect[3], px[3]) - max(rect[1], px[1]))
        inter = ix * iy
        verdict = f"COVERS by {iy}px  <<< BUG" if inter > 0 else "clear"
        print(f"        popup ∩ {label:12} = {inter}px²   {verdict}")


def cmd_annotate(args):
    from PIL import Image, ImageDraw
    png = args[0]
    img = Image.open(png).convert("RGB")
    draw = ImageDraw.Draw(img)
    i = 1
    while i + 1 < len(args) + 1 and i < len(args):
        x = int(args[i]); y = int(args[i + 1])
        label = ""
        nxt = i + 2
        if nxt < len(args) and not args[nxt].lstrip("-").isdigit():
            label = args[nxt]; i += 1
        r = 22
        draw.line([(x - r, y), (x + r, y)], fill=(255, 0, 0), width=4)
        draw.line([(x, y - r), (x, y + r)], fill=(255, 0, 0), width=4)
        draw.ellipse([x - r, y - r, x + r, y + r], outline=(255, 0, 0), width=3)
        if label:
            draw.text((x + r + 4, y - 8), label, fill=(255, 0, 0))
        i += 2
    out = png.rsplit(".", 1)[0] + "_annotated.png"
    img.save(out)
    print("wrote", out)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "boxes":
        cmd_boxes()
    elif cmd == "reveal":
        cmd_reveal(rest[0])
    elif cmd == "lookup":
        cmd_lookup(rest[0], rest[1] if len(rest) > 1 else 0.12)
    elif cmd == "overlap":
        cmd_overlap(rest[0])
    elif cmd == "frame":
        cmd_frame(rest[0], rest[1] if len(rest)>1 else None)
    elif cmd == "cover":
        cmd_cover(rest[0])
    elif cmd == "eval":
        print(cdp_eval(rest[0]))
    elif cmd == "annotate":
        cmd_annotate(rest)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
