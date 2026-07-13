#!/usr/bin/env python3
"""
manga_popup_regression.py — real-manga regression test for the "dictionary popup
covers the tapped word" bug.

For every OCR box on the currently-open manga page (optionally sweeping the next
N pages), it looks the box up and asserts the dictionary popup does NOT overlap
the box's RENDERED TEXT EXTENT (a Range over the box contents — which includes
glyphs like ！？ that overflow mokuro's fixed-height box). Overlap is measured
deterministically from the popup's actual on-screen view bounds vs the text
extent, both in device pixels — no eyeballing.

This is the end-to-end guard the layout unit tests can't be: the overflow is a
browser rendering artifact, so it only reproduces in a real WebView.

Prereqs: emulator running the debug build, a Term dictionary imported, a mokuro
manga open in the reader. Run:  python3 tools/manga_popup_regression.py [pages]
Exit code 0 = all boxes clear, 1 = at least one box covered.
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import manga_debug as md  # noqa: E402


def dismiss_popup():
    # Tap an empty area near the top to close any open popup.
    md.sh("shell", "input", "tap", "120", "260")
    time.sleep(0.4)


def next_page():
    # The reader's next-page control lives bottom-right.
    md.sh("shell", "input", "tap", "980", "2300")
    time.sleep(1.2)


def check_box(idx):
    """Look box idx up and return (covered_px, text_overflow_px, selected)."""
    boxes, _, _ = md._boxes()
    box = boxes[idx]
    b = box["css"]
    # Look up near the OVERFLOW-prone end (bottom of a vertical box / right of a
    # horizontal one) where trailing punctuation tends to spill out.
    if box["vertical"]:
        cx, cy = b["x"] + b["w"] / 2, b["y"] + b["h"] * 0.85
    else:
        cx, cy = b["x"] + b["w"] * 0.85, b["y"] + b["h"] / 2
    r1 = md._handle_tap(cx, cy)
    sel = r1
    if r1 == "__revealed__":
        sel = md._handle_tap(cx, cy)
    time.sleep(1.2)

    import json
    data = json.loads(md.cdp_eval(md.TEXT_EXTENT_JS.replace("IDX", str(idx))))
    tb, bx = data["text"], data["box"]
    popup = md._popup_view_rect()
    overflow = tb["bottom"] - bx["bottom"]
    if not popup:
        return None, overflow, sel, None  # no popup (no dict hit) — skip
    ix = max(0, min(tb["right"], popup[2]) - max(tb["x"], popup[0]))
    iy = max(0, min(tb["bottom"], popup[3]) - max(tb["y"], popup[1]))
    covered = iy if ix > 0 else 0
    # Sliver-bug witness: the popup must be a usable size, not collapsed to ~1px.
    popup_w, popup_h = popup[2] - popup[0], popup[3] - popup[1]
    sliver = popup_w < 150 or popup_h < 150  # device px; well below a usable popup
    return covered, overflow, sel, sliver


def main():
    pages = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    failures = []
    checked = 0
    for page in range(pages):
        try:
            boxes, _, _ = md._boxes()
        except SystemExit:
            print(f"page {page}: no manga page found; stopping")
            break
        print(f"\n=== page {page + 1}: {len(boxes)} OCR boxes ===")
        for i, box in enumerate(boxes):
            dismiss_popup()
            covered, overflow, sel, sliver = check_box(i)
            if covered is None:
                print(f"  box[{i}] '{box['text']}': no popup (no dict hit for '{sel}') — skipped")
                continue
            checked += 1
            problems = []
            if covered > 0:
                problems.append(f"COVERED by {covered}px")
            if sliver:
                problems.append("SLIVER popup (unusably small)")
            status = "OK (clear)" if not problems else " + ".join(problems) + "  <<< FAIL"
            print(f"  box[{i}] '{box['text']}' (overflow {overflow}px): {status}")
            if problems:
                failures.append((page + 1, i, box["text"], "; ".join(problems)))
        dismiss_popup()
        if page < pages - 1:
            next_page()

    print(f"\n===== {checked} boxes checked, {len(failures)} failed =====")
    for pg, i, text, why in failures:
        print(f"  FAIL page {pg} box {i} '{text}': {why}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
