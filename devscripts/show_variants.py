"""并排看同一块在 原图 / renderer=default / manga2eng / manga2eng_pillow 下的渲染效果。

用法: python devscripts/show_variants.py <page> <src_text_keyword>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

RAMP = "@%#*+=-:. "


def ascii_crop(img: Image.Image, box, cols=64) -> list[str]:
    x1, y1, x2, y2 = box
    crop = img.convert("L").crop((max(0, x1), max(0, y1), x2, y2))
    w = cols
    h = max(4, int(crop.height / max(1, crop.width) * w * 0.5))
    arr = np.asarray(crop.resize((w, h), Image.LANCZOS))
    return ["".join(RAMP[min(9, int((255 - v) / 256 * 10))] for v in row) for row in arr]


def side_by_side(panels: list[tuple[str, list[str]]]) -> None:
    n = max(len(p[1]) for p in panels)
    heads = " | ".join(f"{name:<64}" for name, _ in panels)
    print(heads)
    print("-" * len(heads))
    for i in range(n):
        row = " | ".join(f"{(p[1][i] if i < len(p[1]) else ''):<64}" for p in panels)
        print(row)


def main() -> int:
    page = int(sys.argv[1])
    kw = sys.argv[2]
    data = json.loads(Path("epub_test/out/results.json").read_text(encoding="utf-8"))
    blk = None
    for p in data["pages"]:
        if p["index"] == page:
            for b in p["blocks"]:
                if kw in (b.get("src") or ""):
                    blk = b
                    break
    if blk is None:
        print(f"page {page} 里找不到含 {kw!r} 的块"); return 1
    x1, y1, x2, y2 = (int(v) for v in blk["bbox"])
    print(f"page-{page} 块 {kw!r} bbox={x1,y1,x2,y2} ({x2-x1}x{y2-y1})  {blk.get('src')} => {blk.get('dst')}\n")
    pad = 30
    box = (x1 - pad, y1 - pad, x2 + pad, y2 + pad)
    panels = [("原名" if False else "ORIGINAL", ascii_crop(Image.open(Path("epub_test/orig") / f"page-{page:03d}.jpg"), box))]
    for tag, path in [("A default", "epub_test/fontfit/page-100.A.png"),
                      ("C1 manga2eng", "epub_test/fontfit/page-100.C.png"),
                      ("C2 manga2eng_pillow", "epub_test/fontfit/page-100.D.png")]:
        p = Path(path)
        if p.exists():
            panels.append((tag, ascii_crop(Image.open(p), box)))
    side_by_side(panels)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
