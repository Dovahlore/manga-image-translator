"""Visually (ASCII) compare original vs translated crop for specific blocks.

Usage: python devscripts/show_block.py 190 9 190 3 180 6
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

RAMP = "@%#*+=-:. "


def ascii_crop(img: Image.Image, box, cols: int = 84) -> list[str]:
    x1, y1, x2, y2 = box
    crop = img.convert("L").crop((max(0, x1 - 4), max(0, y1 - 4), x2 + 4, y2 + 4))
    w = cols
    h = max(4, int(crop.height / max(1, crop.width) * w * 0.5))
    arr = np.asarray(crop.resize((w, h), Image.LANCZOS))
    return ["".join(RAMP[min(9, int((255 - v) / 256 * 10))] for v in row) for row in arr]


def pair(rows_a: list[str], rows_b: list[str], la: str, lb: str, width: int = 88) -> list[str]:
    n = max(len(rows_a), len(rows_b))
    out = [f"{la:<{width}} | {lb}", "-" * width + "-+-" + "-" * 40]
    for i in range(n):
        a = rows_a[i] if i < len(rows_a) else ""
        b = rows_b[i] if i < len(rows_b) else ""
        out.append(f"{a:<{width}} | {b}")
    return out


def main() -> int:
    args = sys.argv[1:]
    data = json.loads(Path("epub_test/out/results.json").read_text(encoding="utf-8"))
    by_index = {p["index"]: p for p in data["pages"]}
    for k in range(0, len(args), 2):
        idx, blk = int(args[k]), int(args[k + 1])
        page = by_index[idx]
        b = page["blocks"][blk]
        x1, y1, x2, y2 = (int(v) for v in b["bbox"])
        pad = 18
        box = (x1 - pad, y1 - pad, x2 + pad, y2 + pad)
        orig = Image.open(Path("epub_test/orig") / page["file"])
        out = Image.open(Path("epub_test/out") / f"page-{idx:03d}.png")
        if out.size != orig.size:
            out = out.resize(orig.size)
        print(f"\n===== page {idx} block {blk}  bbox_w×h = {x2-x1}x{y2-y1} =====")
        print(f"src: {b.get('src')}")
        print(f"dst: {b.get('dst')}")
        for line in pair(ascii_crop(orig, box), ascii_crop(out, box), "ORIGINAL (JP)", "TRANSLATED (CN)"):
            print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
