"""Per-block rendered-vs-original font size audit.

For every text block: measure the original glyph size (dark components inside the
bbox of the source page) and the rendered glyph size (components of the
out-vs-orig difference mask), then flag blocks where the rendered text is much
bigger than the original.

Usage: python devscripts/analyze_font_size.py [page_index ...]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

RESULTS = Path("epub_test/out/results.json")
ORIG = Path("epub_test/orig")
OUT = Path("epub_test/out")


def comp_sizes(mask: np.ndarray, lo: int = 4, hi: int = 400) -> list[tuple[int, int]]:
    lab, n = ndimage.label(mask)
    if not n:
        return []
    out = []
    for sl in ndimage.find_objects(lab):
        h = sl[0].stop - sl[0].start
        w = sl[1].stop - sl[1].start
        if lo <= h <= hi and 2 <= w <= hi:
            out.append((h, w))
    return out


def median_h(sizes: list[tuple[int, int]]) -> int:
    return int(np.median([h for h, _ in sizes])) if sizes else 0


def main() -> int:
    wanted = {int(x) for x in sys.argv[1:]} or None
    data = json.loads(RESULTS.read_text(encoding="utf-8"))
    print(f"{'page':>4} {'blk':>3} {'box w×h':>10} {'JP h':>5} {'CN h':>5} {'x':>5}  src => dst")
    flagged = []
    for page in data["pages"]:
        idx = page["index"]
        if wanted and idx not in wanted:
            continue
        orig = Image.open(ORIG / page["file"]).convert("L")
        out = Image.open(OUT / f"page-{idx:03d}.png").convert("L")
        if out.size != orig.size:
            out = out.resize(orig.size)
        oa = np.asarray(orig).astype(np.int16)
        ba = np.asarray(out).astype(np.int16)
        diff = np.abs(ba - oa) > 50
        for i, b in enumerate(page.get("blocks", [])):
            x1, y1, x2, y2 = (int(v) for v in b["bbox"])
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(oa.shape[1], x2), min(oa.shape[0], y2)
            if x2 - x1 < 6 or y2 - y1 < 6:
                continue
            jp = median_h(comp_sizes(oa[y1:y2, x1:x2] < 128, lo=5, hi=200))
            cn = median_h(comp_sizes(diff[y1:y2, x1:x2], lo=5, hi=400))
            ratio = (cn / jp) if jp else 0
            src = (b.get("src") or "")[:26]
            dst = (b.get("dst") or "")[:26]
            mark = "  <<< 超大" if ratio >= 1.8 else ""
            print(f"{idx:>4} {i:>3} {x2-x1:>4}x{y2-y1:<4} {jp:>5} {cn:>5} {ratio:>5.2f}  {src} => {dst}{mark}")
            if ratio >= 1.8:
                flagged.append((idx, i, x2 - x1, y2 - y1, jp, cn, round(ratio, 2), src, dst))
    print(f"\nflagged (rendered >= 1.8x original glyph height): {len(flagged)}")
    for f in flagged:
        print("   ", f)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
